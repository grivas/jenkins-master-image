# Jenkins image

docker build -t jenkins-image ./docker
docker run -d -v $(pwd)/configuration:/etc/jenkins/ jenkins-image
